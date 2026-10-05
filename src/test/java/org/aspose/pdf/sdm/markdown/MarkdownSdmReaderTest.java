package org.aspose.pdf.sdm.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.aspose.pdf.sdm.CodeBlock;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.LinkInline;
import org.aspose.pdf.sdm.ListBlock;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Quote;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TextStyle;
import org.aspose.pdf.sdm.ThematicBreak;
import org.junit.jupiter.api.Test;

/**
 * Unit gate for {@link MarkdownSdmReader}: each supported Markdown construct
 * parses to the right SDM node. Pure in-memory — no disk access.
 */
public class MarkdownSdmReaderTest {

    private static SdmDocument read(String md) {
        return new MarkdownSdmReader().read(md);
    }

    @Test
    public void atxHeading() {
        SdmDocument doc = read("## Hello World\n");
        assertTrue(doc.getChildren().get(0) instanceof Heading);
        Heading h = (Heading) doc.getChildren().get(0);
        assertEquals(2, h.getLevel());
        assertEquals("Hello World", ((Run) h.getInline().get(0)).getText());
    }

    @Test
    public void setextHeading() {
        SdmDocument doc = read("Title\n=====\n");
        assertTrue(doc.getChildren().get(0) instanceof Heading);
        assertEquals(1, ((Heading) doc.getChildren().get(0)).getLevel());
    }

    @Test
    public void boldItalicStrike() {
        SdmDocument doc = read("This is **bold**, *italic* and ~~gone~~.\n");
        Paragraph p = (Paragraph) doc.getChildren().get(0);
        boolean bold = false, italic = false, strike = false;
        for (SdmInline in : p.getInline()) {
            if (in instanceof Run) {
                TextStyle st = ((Run) in).getStyle();
                if (st != null && st.isBold()) {
                    bold = true;
                }
                if (st != null && st.isItalic()) {
                    italic = true;
                }
                if (st != null && st.isStrikethrough()) {
                    strike = true;
                }
            }
        }
        assertTrue(bold, "bold");
        assertTrue(italic, "italic");
        assertTrue(strike, "strike");
    }

    @Test
    public void link() {
        SdmDocument doc = read("See [the site](https://example.com) now.\n");
        Paragraph p = (Paragraph) doc.getChildren().get(0);
        LinkInline link = null;
        for (SdmInline in : p.getInline()) {
            if (in instanceof LinkInline) {
                link = (LinkInline) in;
            }
        }
        assertEquals("https://example.com", link.getHref());
        assertEquals("the site", ((Run) link.getChildren().get(0)).getText());
    }

    @Test
    public void unorderedList() {
        SdmDocument doc = read("- apple\n- banana\n- cherry\n");
        assertTrue(doc.getChildren().get(0) instanceof ListBlock);
        ListBlock ul = (ListBlock) doc.getChildren().get(0);
        assertFalse(ul.isOrdered());
        assertEquals(3, ul.getItems().size());
    }

    @Test
    public void orderedListWithStart() {
        SdmDocument doc = read("3. third\n4. fourth\n");
        ListBlock ol = (ListBlock) doc.getChildren().get(0);
        assertTrue(ol.isOrdered());
        assertEquals(Integer.valueOf(3), ol.getStart());
        assertEquals(2, ol.getItems().size());
    }

    @Test
    public void nestedList() {
        SdmDocument doc = read("- parent\n  - child1\n  - child2\n");
        ListBlock ul = (ListBlock) doc.getChildren().get(0);
        assertEquals(1, ul.getItems().size());
        // The item holds a paragraph plus a nested list.
        List<SdmBlock> kids = ul.getItems().get(0).getChildren();
        boolean hasNested = false;
        for (SdmBlock b : kids) {
            if (b instanceof ListBlock) {
                hasNested = true;
                assertEquals(2, ((ListBlock) b).getItems().size());
            }
        }
        assertTrue(hasNested, "nested list present");
    }

    @Test
    public void fencedCode() {
        SdmDocument doc = read("```java\nint x = 1;\n```\n");
        assertTrue(doc.getChildren().get(0) instanceof CodeBlock);
        CodeBlock c = (CodeBlock) doc.getChildren().get(0);
        assertEquals("java", c.getLanguage());
        assertEquals("int x = 1;", c.getText());
    }

    @Test
    public void blockQuote() {
        SdmDocument doc = read("> quoted line one\n> quoted line two\n");
        assertTrue(doc.getChildren().get(0) instanceof Quote);
    }

    @Test
    public void thematicBreak() {
        SdmDocument doc = read("para\n\n---\n\nmore\n");
        boolean hasRule = false;
        for (SdmBlock b : doc.getChildren()) {
            if (b instanceof ThematicBreak) {
                hasRule = true;
            }
        }
        assertTrue(hasRule, "thematic break present");
    }

    @Test
    public void gfmTable() {
        SdmDocument doc = read("| Name | Age |\n| --- | ---: |\n| Alice | 30 |\n| Bob | 25 |\n");
        assertTrue(doc.getChildren().get(0) instanceof Table);
        Table t = (Table) doc.getChildren().get(0);
        // 1 header + 2 body rows.
        assertEquals(3, t.getRows().size());
        assertEquals(2, t.getColumns().size());
    }

    @Test
    public void frontMatter() {
        SdmDocument doc = read("---\ntitle: My Title\nauthor: Jane\n---\n\nBody text.\n");
        assertEquals("My Title", doc.getMetadata().getTitle());
        assertEquals("Jane", doc.getMetadata().getAuthor());
        assertTrue(doc.getChildren().get(0) instanceof Paragraph);
    }

    @Test
    public void inlineCode() {
        SdmDocument doc = read("Use `code` here.\n");
        Paragraph p = (Paragraph) doc.getChildren().get(0);
        boolean mono = false;
        for (SdmInline in : p.getInline()) {
            if (in instanceof Run && "code".equals(((Run) in).getText())) {
                TextStyle st = ((Run) in).getStyle();
                mono = st != null && "monospace".equals(st.getFontFamily());
            }
        }
        assertTrue(mono, "inline code -> monospace run");
    }

    @Test
    public void escapedAsteriskIsLiteral() {
        SdmDocument doc = read("a \\* b\n");
        Paragraph p = (Paragraph) doc.getChildren().get(0);
        StringBuilder text = new StringBuilder();
        for (SdmInline in : p.getInline()) {
            if (in instanceof Run) {
                text.append(((Run) in).getText());
            }
        }
        assertTrue(text.toString().contains("*"), text.toString());
    }
}
